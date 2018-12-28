# -*- coding:utf-8 -*-

import sqlite3

# 数据库处理类
class DataBase(object):
    def __init__(self, Name):
        self.DatabaseName = Name
        self.con = None
        self.cu = None
        self.data = None
        
    def Create(self):
        self.con = sqlite3.connect(self.DatabadeName)
        self.cu = con.cursor()
        
    def Execute(self, QueryCommand):
        self.cu.execute(QueryCommand)
        
    def GetResult(self):
        self.data = cu.fetchall()
        return self.data
        
    def __del__(self):
        self.cu.close()
        self.con.close()
        
      
def testDatabase():
    database = DataBase("money.db")
    quretext = "select Time, Name, Amount, TypeName from Income, IncomeType where IncomeType.TypeID == Income.TypeID"
    database.Execute(quretext)
    data = database.GetResult()
    print(data)
    
    
if __name__ == "__main__":
    testDatabase()
        